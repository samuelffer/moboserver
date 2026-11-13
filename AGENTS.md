# Guia universal — builds, ZIPs e links de download

> **Como usar:** copie este arquivo para a raiz do seu repositório como `AGENTS.md` e peça à IA para implementar ou alterar o projeto. Este é um modelo genérico: adapte-o às necessidades do projeto e às instruções do usuário. Ele **não é**, por si só, um workflow do GitHub Actions; o agente deve criar ou adaptar o workflow real em `.github/workflows/`.

## Objetivo

Quando a solicitação exigir um programa ou outro arquivo pronto para uso, gerar, testar e disponibilizar um ZIP que contenha os arquivos necessários para o usuário executar, instalar ou utilizar o resultado. Ao concluir, fornecer um link verificado para o ZIP e, quando disponível, o link do artefato do GitHub Actions.

Não assuma que todo projeto gera um `.exe`. Exemplos: aplicativo Windows, APK Android, ferramenta de linha de comando, site estático, biblioteca ou outro produto. Respeite a plataforma, o formato e os arquivos explicitamente pedidos. Para projetos com múltiplas plataformas, identifique claramente o conteúdo e gere ZIPs separados ou um ZIP único, conforme a solicitação.

## 1. Antes de implementar

1. Leia as instruções existentes, o README, os arquivos de build, o `.gitignore` e os workflows. Preserve o trabalho já existente e respeite instruções de maior prioridade. Não substitua este documento cegamente se já houver um `AGENTS.md` com regras próprias.
2. Identifique a branch atual, o repositório, a plataforma alvo, a linguagem, os comandos de compilação e testes, os arquivos que são realmente necessários e os requisitos para executar o produto. Não invente nomes, caminhos, versões, comandos ou resultados.
3. Confira se o usuário quer um executável portátil, instalador, pacote autocontido ou dependente de runtime. Se isso for essencial e estiver ambíguo, pergunte; caso contrário, faça uma escolha razoável e documente-a. **Não retire DLLs, recursos ou dependências necessários apenas para deixar o ZIP menor.**
4. Verifique se o GitHub Actions, as permissões e a plataforma necessária estão disponíveis. Para builds que dependam de chaves, licenças, contas, assinatura, hardware específico ou serviços pagos, explique o bloqueio; nunca peça senhas ou tokens no chat e nunca inclua segredos no repositório ou no ZIP.

## 2. Build e testes

- Use o ambiente adequado à plataforma, por exemplo `windows-latest` para ferramentas Windows quando necessário; use os sistemas e toolchains suportados pelo projeto, fixando versões quando apropriado. O agente deve criar um workflow funcional em vez de copiar comandos de outro projeto sem adaptação.
- Compile o código da revisão solicitada e execute testes relevantes. Se não houver testes, faça verificações proporcionais e explique a cobertura. Não afirme que algo foi testado em dispositivo, sistema ou jogo real se isso não ocorreu.
- Valide que os arquivos esperados existem e que o ZIP pode ser aberto. Verifique seu conteúdo e a ausência de arquivos temporários, segredos, dados pessoais e dependências desnecessárias. Quando possível, teste uma extração limpa e os pontos de entrada do pacote.
- Inclua instruções breves de uso, requisitos e licenças/avisos necessários. Um EXE dependente do runtime **não** é autocontido: informe o runtime e a versão necessários. Se o usuário pedir para não incluir runtimes, não os distribua, mas documente a instalação deles.
- Não empacote códigos de assinatura, configurações pessoais, saves de usuários, diretórios `.git`, caches, dados sensíveis ou ferramentas de desenvolvimento, salvo quando expressamente necessários e autorizados. Respeite licenças e tamanho do repositório.

## 3. Versionamento e pasta `Zips/`

- Para cada versão ou revisão entregue, gere um nome inequívoco, como `Zips/<projeto>-<versão>-<plataforma>.zip`. Substitua os campos pelo nome, versão e plataforma reais. Mantenha as versões anteriores, a menos que o usuário determine outra política.
- O ZIP deve conter o que foi solicitado e tudo o que é necessário para uso, além das instruções e licenças pertinentes. Se houver mais de um produto, deixe a estrutura ou os nomes dos arquivos claros.
- Nunca sobrescreva silenciosamente um ZIP já publicado com a mesma versão. Gere uma nova versão/revisão quando os arquivos mudarem.
- **Não crie GitHub Releases nem tags automaticamente.** Se o projeto já adotar outra política de distribuição, ou se o usuário pedir outra, respeite-a.
- Manter binários versionados no Git aumenta o histórico e pode ultrapassar os limites do GitHub. Antes de adicionar arquivos grandes ou muitos pacotes, avalie as regras do repositório e confirme uma alternativa adequada com o usuário. GitHub Actions artifacts são uma alternativa temporária, não um substituto permanente por padrão.
- Alterações apenas de documentação, sem impacto no pacote, não exigem rebuild ou uma versão nova, salvo se o usuário ou as regras do projeto exigirem uma nova entrega.

## 4. GitHub Actions e publicação

1. Configure um workflow para checkout da revisão correta, instalação das dependências de build, compilação, testes, criação e verificação dos ZIPs e upload dos artefatos. Faça o job falhar quando um passo obrigatório ou um arquivo esperado faltar.
2. Use `actions/upload-artifact` em versão suportada e configure `if-no-files-found: error`. Defina retenção conforme a política do repositório. Se fizer upload de um ZIP como artefato, explique que o download do GitHub pode vir como **um ZIP externo contendo o ZIP final**.
3. Publique na branch correta somente com autorização e permissões adequadas. Se o usuário autorizar publicação automática em `Zips/`, o workflow pode criar um commit contendo **somente os ZIPs novos**, depois que todos os testes e verificações passarem. Limite a publicação a eventos e branches confiáveis; não publique a partir de código não confiável de pull requests ou forks. Use a menor permissão necessária e não exponha credenciais.
4. Não use force push, não faça merge automático e não substitua ZIPs existentes. Evite corridas com outras alterações: faça checkout da revisão a ser compilada e falhe de forma segura se a branch avançar antes do push; não use um pull automático que aplique um binário compilado sobre outro código. Evite loops de CI para commits que alteram apenas `Zips/`.
5. Aguarde a execução correspondente à revisão correta e verifique o resultado. Após um eventual commit do workflow, sincronize a cópia local com segurança, sem descartar alterações não commitadas. Caso o workflow falhe, a publicação seja bloqueada ou não haja permissão, relate o motivo e não diga que o ZIP está pronto no repositório.

Exemplo de **passo de upload** para adaptar ao workflow real, depois de compilar e validar o pacote:

```yaml
- name: Enviar ZIP como artefato
  uses: actions/upload-artifact@v4
  with:
    name: pacote-da-versao
    path: Zips/nome-real-do-pacote.zip
    if-no-files-found: error
```

Os valores do exemplo devem ser substituídos por nomes e caminhos reais. Ele não inclui os passos de build, teste, criação do ZIP nem publicação em Git; esses passos dependem do projeto.

## 5. Links ao concluir

- Confirme primeiro que o ZIP existe no repositório remoto e na branch correta. Para um repositório público no GitHub, o formato do link direto é:

  `https://github.com/<dono>/<repositorio>/raw/refs/heads/<branch>/Zips/<arquivo-real>.zip`

  Substitua todos os campos por valores reais e codifique caracteres especiais na URL quando necessário. Não invente links. Se possível, confirme que o endereço aponta para o arquivo correto.
- Um repositório privado exige acesso autorizado: não prometa um link público anônimo. Não compartilhe URLs temporárias assinadas nem credenciais como se fossem links permanentes.
- Se houver artefato, obtenha o ID real da execução e do artefato no GitHub e forneça o link correspondente:

  `https://github.com/<dono>/<repositorio>/actions/runs/<run_id>/artifacts/<artifact_id>`

  Artefatos normalmente exigem login e acesso ao repositório e **expiram** conforme a retenção; excluir a execução também os remove. O link do artefato não é garantia de download permanente.
- Na resposta final, indique o link do ZIP, plataforma, requisitos, o que ele contém e eventuais limitações ou testes não realizados. Se a publicação não for possível, ofereça o artefato ou o arquivo local apenas se realmente existir e informe claramente a limitação.
